/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2001-2002 Kana Software, Inc.
// Copyright (C) 2001-2010 Julian Hyde and others
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
//
// jhyde, 26 July, 2001
*/

package mondrian.rolap;

import java.io.*;
import java.lang.ref.SoftReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import javax.sql.DataSource;

import mondrian.olap.*;
import mondrian.olap.fun.*;
import mondrian.olap.type.MemberType;
import mondrian.olap.type.NumericType;
import mondrian.olap.type.StringType;
import mondrian.olap.type.Type;
import mondrian.resource.MondrianResource;
import mondrian.rolap.aggmatcher.AggTableManager;
import mondrian.rolap.aggmatcher.JdbcSchema;
import mondrian.spi.*;

import org.apache.log4j.Logger;
import org.apache.commons.vfs.*;

import org.eigenbase.xom.*;
import org.eigenbase.xom.Parser;
import org.olap4j.impl.Olap4jUtil;

/**
 * A <code>RolapSchema</code> is a collection of {@link RolapCube}s and
 * shared {@link RolapDimension}s. It is shared betweeen {@link
 * RolapConnection}s. It caches {@link MemberReader}s, etc.
 *
 * @see RolapConnection
 * @author jhyde
 * @since 26 July, 2001
 * @version $Id$
 */
public class RolapSchema implements Schema {
    private static final Logger LOGGER = Logger.getLogger(RolapSchema.class);

    private static final Set<Access> schemaAllowed =
        Olap4jUtil.enumSetOf(Access.NONE, Access.ALL, Access.ALL_DIMENSIONS);

    private static final Set<Access> cubeAllowed =
        Olap4jUtil.enumSetOf(Access.NONE, Access.ALL);

    private static final Set<Access> dimensionAllowed =
        Olap4jUtil.enumSetOf(Access.NONE, Access.ALL);

    private static final Set<Access> hierarchyAllowed =
        Olap4jUtil.enumSetOf(Access.NONE, Access.ALL, Access.CUSTOM);

    private static final Set<Access> memberAllowed =
        Olap4jUtil.enumSetOf(Access.NONE, Access.ALL);

    private String name;

    /**
     * Internal use only.
     */
    private final RolapConnection internalConnection;
    /**
     * Holds cubes in this schema.
     */
    private final Map<String, RolapCube> mapNameToCube;
    /**
     * Maps {@link String shared hierarchy name} to {@link MemberReader}.
     * Shared between all statements which use this connection.
     */
    private final Map<String, MemberReader> mapSharedHierarchyToReader;

    /**
     * Maps {@link String names of shared hierarchies} to {@link
     * RolapHierarchy the canonical instance of those hierarchies}.
     */
    private final Map<String, RolapHierarchy> mapSharedHierarchyNameToHierarchy;
    /**
     * The default role for connections to this schema.
     */
    private RoleImpl defaultRole;

    private final String md5Bytes;

    /**
     * A schema's aggregation information
     */
    private AggTableManager aggTableManager;

    /**
     * This is basically a unique identifier for this RolapSchema instance
     * used it its equals and hashCode methods.
     */
    private String key;

    /**
     * Maps {@link String names of roles} to {@link Role roles with those names}.
     */
    private final Map<String, Role> mapNameToRole;

    /**
     * Maps {@link String names of sets} to {@link NamedSet named sets}.
     */
    private final Map<String, NamedSet> mapNameToSet =
        new HashMap<String, NamedSet>();

    /**
     * Table containing all standard MDX functions, plus user-defined functions
     * for this schema.
     */
    private FunTable funTable;

    private MondrianDef.Schema xmlSchema;

    final List<RolapSchemaParameter > parameterList =
        new ArrayList<RolapSchemaParameter >();

    private Date schemaLoadDate;

    private DataSourceChangeListener dataSourceChangeListener;

    /**
     * Map containing column cardinality. The combination of
     * Mondrianef.Relation and MondrianDef.Expression uniquely
     * identifies a relational expression(e.g. a column) specified
     * in the xml schema.
     */
    private final Map<
        MondrianDef.Relation,
        Map<MondrianDef.Expression, Integer>>
        relationExprCardinalityMap;

    /**
     * List of warnings. Populated when a schema is created by a connection
     * that has
     * {@link mondrian.rolap.RolapConnectionProperties#Ignore Ignore}=true.
     */
    private final List<Exception> warningList = new ArrayList<Exception>();
    private Map<String, Annotation> annotationMap;

    /**
     * This is ONLY called by other constructors (and MUST be called
     * by them) and NEVER by the Pool.
     *
     * @param key Key
     * @param connectInfo Connect properties
     * @param dataSource Data source
     * @param md5Bytes MD5 hash
     */
    private RolapSchema(
        final String key,
        final Util.PropertyList connectInfo,
        final DataSource dataSource,
        final String md5Bytes)
    {
        this.key = key;
        this.md5Bytes = md5Bytes;
        // the order of the next two lines is important
        this.defaultRole = createDefaultRole();
        this.internalConnection =
            new RolapConnection(connectInfo, this, dataSource);

        this.mapSharedHierarchyNameToHierarchy =
            new HashMap<String, RolapHierarchy>();
        this.mapSharedHierarchyToReader = new HashMap<String, MemberReader>();
        this.mapNameToCube = new HashMap<String, RolapCube>();
        this.mapNameToRole = new HashMap<String, Role>();
        this.aggTableManager = new AggTableManager(this);
        this.dataSourceChangeListener =
            createDataSourceChangeListener(connectInfo);
        this.relationExprCardinalityMap =
            new HashMap<
                MondrianDef.Relation,
                Map<MondrianDef.Expression, Integer>>();
    }

    /**
     * Create RolapSchema given the MD5 hash, catalog name and string (content)
     * and the connectInfo object.
     *
     * @param md5Bytes may be null
     * @param catalogUrl URL of catalog
     * @param catalogStr may be null
     * @param connectInfo Connection properties
     */
    private RolapSchema(
            final String key,
            final String md5Bytes,
            final String catalogUrl,
            final String catalogStr,
            final Util.PropertyList connectInfo,
            final DataSource dataSource)
    {
        this(key, connectInfo, dataSource, md5Bytes);
        load(catalogUrl, catalogStr);
    }

    private RolapSchema(
            final String key,
            final String catalogUrl,
            final Util.PropertyList connectInfo,
            final DataSource dataSource)
    {
        this(key, connectInfo, dataSource, null);
        load(catalogUrl, null);
    }

    protected void finalCleanUp() {
        if (aggTableManager != null) {
            aggTableManager.finalCleanUp();
            aggTableManager = null;
        }
    }

    protected void finalize() throws Throwable {
        super.finalize();
        finalCleanUp();
    }

    public boolean equals(Object o) {
        if (!(o instanceof RolapSchema)) {
            return false;
        }
        RolapSchema other = (RolapSchema) o;
        return other.key.equals(key);
    }

    public int hashCode() {
        return key.hashCode();
    }

    protected Logger getLogger() {
        return LOGGER;
    }

    /**
     * Method called by all constructors to load the catalog into DOM and build
     * application mdx and sql objects.
     *
     * @param catalogUrl URL of catalog
     * @param catalogStr Text of catalog, or null
     */
    protected void load(String catalogUrl, String catalogStr) {
        try {
            final Parser xmlParser = XOMUtil.createDefaultParser();

            final DOMWrapper def;
            if (catalogStr == null) {
                InputStream in = null;
                try {
                    in = Util.readVirtualFile(catalogUrl);
                    def = xmlParser.parse(in);
                } finally {
                    if (in != null) {
                        in.close();
                    }
                }

                if (getLogger().isDebugEnabled()) {
                    try {
                        StringBuilder buf = new StringBuilder(1000);
                        InputStream debugIn = Util.readVirtualFile(catalogUrl);
                        int n;
                        while ((n = debugIn.read()) != -1) {
                            buf.append((char) n);
                        }
                        getLogger().debug(
                            "RolapSchema.load: content: \n" + buf.toString());
                    } catch (java.io.IOException ex) {
                        getLogger().debug("RolapSchema.load: ex=" + ex);
                    }
                }

            } else {
                if (getLogger().isDebugEnabled()) {
                    getLogger().debug(
                        "RolapSchema.load: catalogStr: \n" + catalogStr);
                }

                def = xmlParser.parse(catalogStr);
            }

            xmlSchema = new MondrianDef.Schema(def);

            if (getLogger().isDebugEnabled()) {
                StringWriter sw = new StringWriter(4096);
                PrintWriter pw = new PrintWriter(sw);
                pw.println("RolapSchema.load: dump xmlschema");
                xmlSchema.display(pw, 2);
                pw.flush();
                getLogger().debug(sw.toString());
            }

            load(xmlSchema);
        } catch (XOMException e) {
            throw Util.newError(e, "while parsing catalog " + catalogUrl);
        } catch (FileSystemException e) {
            throw Util.newError(e, "while parsing catalog " + catalogUrl);
        } catch (IOException e) {
            throw Util.newError(e, "while parsing catalog " + catalogUrl);
        }

        aggTableManager.initialize();
        setSchemaLoadDate();
    }

    private void setSchemaLoadDate() {
        schemaLoadDate = new Date();
    }

    public Date getSchemaLoadDate() {
        return schemaLoadDate;
    }

    public List<Exception> getWarnings() {
        return Collections.unmodifiableList(warningList);
    }

    RoleImpl getDefaultRole() {
        return defaultRole;
    }

    public MondrianDef.Schema getXMLSchema() {
        return xmlSchema;
    }

    public String getName() {
        Util.assertPostcondition(name != null, "return != null");
        Util.assertPostcondition(name.length() > 0, "return.length() > 0");
        return name;
    }

    public Map<String, Annotation> getAnnotationMap() {
        return annotationMap;
    }

    /**
     * Returns this schema's SQL dialect.
     *
     * <p>NOTE: This method is not cheap. The implementation gets a connection
     * from the connection pool.
     *
     * @return dialect
     */
    public Dialect getDialect() {
        DataSource dataSource = getInternalConnection().getDataSource();
        return DialectManager.createDialect(dataSource, null);
    }

    private void load(MondrianDef.Schema xmlSchema) {
        this.name = xmlSchema.name;
        if (name == null || name.equals("")) {
            throw Util.newError("<Schema> name must be set");
        }

        this.annotationMap =
            RolapHierarchy.createAnnotationMap(xmlSchema.annotations);
        // Validate user-defined functions. Must be done before we validate
        // calculated members, because calculated members will need to use the
        // function table.
        final Map<String, UserDefinedFunction> mapNameToUdf =
            new HashMap<String, UserDefinedFunction>();
        for (MondrianDef.UserDefinedFunction udf
            : xmlSchema.userDefinedFunctions)
        {
            defineFunction(mapNameToUdf, udf.name, udf.className);
        }
        final RolapSchemaFunctionTable funTable =
            new RolapSchemaFunctionTable(mapNameToUdf.values());
        funTable.init();
        this.funTable = funTable;

        // Validate public dimensions.
        for (MondrianDef.Dimension xmlDimension : xmlSchema.dimensions) {
            if (xmlDimension.foreignKey != null) {
                throw MondrianResource.instance()
                    .PublicDimensionMustNotHaveForeignKey.ex(
                    xmlDimension.name);
            }
        }

        // Create parameters.
        Set<String> parameterNames = new HashSet<String>();
        for (MondrianDef.Parameter xmlParameter : xmlSchema.parameters) {
            String name = xmlParameter.name;
            if (!parameterNames.add(name)) {
                throw MondrianResource.instance().DuplicateSchemaParameter.ex(
                    name);
            }
            Type type;
            if (xmlParameter.type.equals("String")) {
                type = new StringType();
            } else if (xmlParameter.type.equals("Numeric")) {
                type = new NumericType();
            } else {
                type = new MemberType(null, null, null, null);
            }
            final String description = xmlParameter.description;
            final boolean modifiable = xmlParameter.modifiable;
            String defaultValue = xmlParameter.defaultValue;
            RolapSchemaParameter param =
                new RolapSchemaParameter(
                    this, name, defaultValue, description, type, modifiable);
            Util.discard(param);
        }

        // Create cubes.
        for (MondrianDef.Cube xmlCube : xmlSchema.cubes) {
            if (xmlCube.isEnabled()) {
                RolapCube cube = new RolapCube(this, xmlSchema, xmlCube, true);
                Util.discard(cube);
            }
        }

        // Create virtual cubes.
        for (MondrianDef.VirtualCube xmlVirtualCube : xmlSchema.virtualCubes) {
            if (xmlVirtualCube.isEnabled()) {
                RolapCube cube =
                    new RolapCube(this, xmlSchema, xmlVirtualCube, true);
                Util.discard(cube);
            }
        }

        // Create named sets.
        for (MondrianDef.NamedSet xmlNamedSet : xmlSchema.namedSets) {
            mapNameToSet.put(xmlNamedSet.name, createNamedSet(xmlNamedSet));
        }

        // Create roles.
        for (MondrianDef.Role xmlRole : xmlSchema.roles) {
            Role role = createRole(xmlRole);
            mapNameToRole.put(xmlRole.name, role);
        }

        // Set default role.
        if (xmlSchema.defaultRole != null) {
            Role role = lookupRole(xmlSchema.defaultRole);
            if (role == null) {
                error(
                    "Role '" + xmlSchema.defaultRole + "' not found",
                    locate(xmlSchema, "defaultRole"));
            } else {
                // At this stage, the only roles in mapNameToRole are
                // RoleImpl roles so it is safe to case.
                defaultRole = (RoleImpl) role;
            }
        }
    }

    /**
     * Returns the location of an element or attribute in an XML document.
     *
     * <p>TODO: modify eigenbase-xom parser to return position info
     *
     * @param node Node
     * @param attributeName Attribute name, or null
     * @return Location of node or attribute in an XML document
     */
    XmlLocation locate(ElementDef node, String attributeName) {
        return null;
    }

    /**
     * Reports an error. If we are tolerant of errors
     * (see {@link mondrian.rolap.RolapConnectionProperties#Ignore}), adds
     * it to the stack, overwise throws. A thrown exception will typically
     * abort the attempt to create the exception.
     *
     * @param message Message
     * @param xmlLocation Location of XML element or attribute that caused
     * the error, or null
     */
    void error(
        String message,
        XmlLocation xmlLocation)
    {
        final RuntimeException ex = new RuntimeException(message);
        if (internalConnection != null
            && "true".equals(
            internalConnection.getProperty(
                RolapConnectionProperties.Ignore.name())))
        {
            warningList.add(ex);
        } else {
            throw ex;
        }
    }

    private NamedSet createNamedSet(MondrianDef.NamedSet xmlNamedSet) {
        final String formulaString = xmlNamedSet.getFormula();
        final Exp exp;
        try {
            exp = getInternalConnection().parseExpression(formulaString);
        } catch (Exception e) {
            throw MondrianResource.instance().NamedSetHasBadFormula.ex(
                    xmlNamedSet.name, e);
        }
        final Formula formula =
            new Formula(
                new Id(
                    new Id.Segment(
                        xmlNamedSet.name,
                        Id.Quoting.UNQUOTED)),
                exp);
        return formula.getNamedSet();
    }

    private Role createRole(MondrianDef.Role xmlRole) {
        if (xmlRole.union != null) {
            if (xmlRole.schemaGrants != null
                && xmlRole.schemaGrants.length > 0)
            {
                throw MondrianResource.instance().RoleUnionGrants.ex();
            }
            List<Role> roleList = new ArrayList<Role>();
            for (MondrianDef.RoleUsage roleUsage : xmlRole.union.roleUsages) {
                final Role role = mapNameToRole.get(roleUsage.roleName);
                if (role == null) {
                    throw MondrianResource.instance().UnknownRole.ex(
                        roleUsage.roleName);
                }
                roleList.add(role);
            }
            return RoleImpl.union(roleList);
        }
        RoleImpl role = new RoleImpl();
        for (MondrianDef.SchemaGrant schemaGrant : xmlRole.schemaGrants) {
            role.grant(this, getAccess(schemaGrant.access, schemaAllowed));
            for (MondrianDef.CubeGrant cubeGrant : schemaGrant.cubeGrants) {
                RolapCube cube = lookupCube(cubeGrant.cube);
                if (cube == null) {
                    throw Util.newError(
                        "Unknown cube '" + cubeGrant.cube + "'");
                }
                role.grant(cube, getAccess(cubeGrant.access, cubeAllowed));
                final SchemaReader schemaReader = cube.getSchemaReader(null);
                for (MondrianDef.DimensionGrant dimensionGrant
                    : cubeGrant.dimensionGrants)
                {
                    Dimension dimension = (Dimension)
                        schemaReader.lookupCompound(
                            cube,
                            Util.parseIdentifier(dimensionGrant.dimension),
                            true,
                            Category.Dimension);
                    role.grant(
                        dimension,
                        getAccess(dimensionGrant.access, dimensionAllowed));
                }
                for (MondrianDef.HierarchyGrant hierarchyGrant
                    : cubeGrant.hierarchyGrants)
                {
                    Hierarchy hierarchy = (Hierarchy)
                        schemaReader.lookupCompound(
                            cube,
                            Util.parseIdentifier(hierarchyGrant.hierarchy),
                            true,
                            Category.Hierarchy);
                    final Access hierarchyAccess =
                        getAccess(hierarchyGrant.access, hierarchyAllowed);
                    Level topLevel = null;
                    if (hierarchyGrant.topLevel != null) {
                        if (hierarchyAccess != Access.CUSTOM) {
                            throw Util.newError(
                                "You may only specify 'topLevel' if "
                                + "access='custom'");
                        }
                        topLevel = (Level) schemaReader.lookupCompound(
                            cube,
                            Util.parseIdentifier(hierarchyGrant.topLevel),
                            true,
                            Category.Level);
                    }
                    Level bottomLevel = null;
                    if (hierarchyGrant.bottomLevel != null) {
                        if (hierarchyAccess != Access.CUSTOM) {
                            throw Util.newError(
                                "You may only specify 'bottomLevel' if "
                                + "access='custom'");
                        }
                        bottomLevel = (Level) schemaReader.lookupCompound(
                            cube,
                            Util.parseIdentifier(hierarchyGrant.bottomLevel),
                            true,
                            Category.Level);
                    }
                    Role.RollupPolicy rollupPolicy;
                    if (hierarchyGrant.rollupPolicy != null) {
                        try {
                            rollupPolicy =
                                Role.RollupPolicy.valueOf(
                                    hierarchyGrant.rollupPolicy.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            throw Util.newError(
                                "Illegal rollupPolicy value '"
                                + hierarchyGrant.rollupPolicy
                                + "'");
                        }
                    } else {
                        rollupPolicy = Role.RollupPolicy.FULL;
                    }
                    role.grant(
                        hierarchy, hierarchyAccess, topLevel, bottomLevel,
                        rollupPolicy);
                    for (MondrianDef.MemberGrant memberGrant
                        : hierarchyGrant.memberGrants)
                    {
                        if (hierarchyAccess != Access.CUSTOM) {
                            throw Util.newError(
                                "You may only specify <MemberGrant> if "
                                + "<Hierarchy> has access='custom'");
                        }
                        final boolean ignoreInvalidMembers =
                            MondrianProperties.instance().IgnoreInvalidMembers
                                .get();
                        Member member = schemaReader.getMemberByUniqueName(
                            Util.parseIdentifier(memberGrant.member),
                            !ignoreInvalidMembers);
                        if (member == null) {
                            // They asked to ignore members that don't exist
                            // (e.g. [Store].[USA].[Foo]), so ignore this grant
                            // too.
                            assert ignoreInvalidMembers;
                            continue;
                        }
                        if (member.getHierarchy() != hierarchy) {
                            throw Util.newError(
                                "Member '" + member
                                + "' is not in hierarchy '" + hierarchy + "'");
                        }
                        role.grant(
                            member,
                            getAccess(memberGrant.access, memberAllowed));
                    }
                }
            }
        }
        role.makeImmutable();
        return role;
    }

    private Access getAccess(String accessString, Set<Access> allowed) {
        final Access access = Access.valueOf(accessString.toUpperCase());
        if (allowed.contains(access)) {
            return access; // value is ok
        }
        throw Util.newError("Bad value access='" + accessString + "'");
    }

    public Dimension createDimension(Cube cube, String xml) {
        MondrianDef.CubeDimension xmlDimension;
        try {
            final Parser xmlParser = XOMUtil.createDefaultParser();
            final DOMWrapper def = xmlParser.parse(xml);
            final String tagName = def.getTagName();
            if (tagName.equals("Dimension")) {
                xmlDimension = new MondrianDef.Dimension(def);
            } else if (tagName.equals("DimensionUsage")) {
                xmlDimension = new MondrianDef.DimensionUsage(def);
            } else {
                throw new XOMException(
                    "Got <" + tagName
                    + "> when expecting <Dimension> or <DimensionUsage>");
            }
        } catch (XOMException e) {
            throw Util.newError(
                e,
                "Error while adding dimension to cube '" + cube
                + "' from XML [" + xml + "]");
        }
        return ((RolapCube) cube).createDimension(xmlDimension, xmlSchema);
    }

    public Cube createCube(String xml) {
        RolapCube cube;
        try {
            final Parser xmlParser = XOMUtil.createDefaultParser();
            final DOMWrapper def = xmlParser.parse(xml);
            final String tagName = def.getTagName();
            if (tagName.equals("Cube")) {
                // Create empty XML schema, to keep the method happy. This is
                // okay, because there are no forward-references to resolve.
                final MondrianDef.Schema xmlSchema = new MondrianDef.Schema();
                MondrianDef.Cube xmlDimension = new MondrianDef.Cube(def);
                cube = new RolapCube(this, xmlSchema, xmlDimension, false);
            } else if (tagName.equals("VirtualCube")) {
                // Need the real schema here.
                MondrianDef.Schema xmlSchema = getXMLSchema();
                MondrianDef.VirtualCube xmlDimension =
                        new MondrianDef.VirtualCube(def);
                cube = new RolapCube(this, xmlSchema, xmlDimension, false);
            } else {
                throw new XOMException(
                    "Got <" + tagName + "> when expecting <Cube>");
            }
        } catch (XOMException e) {
            throw Util.newError(
                e,
                "Error while creating cube from XML [" + xml + "]");
        }
        return cube;
    }

    /**
     * A collection of schemas, identified by their connection properties
     * (catalog name, JDBC URL, and so forth).
     *
     * <p>To lookup a schema, call <code>Pool.instance().{@link #get}</code>.
     */
    static class Pool {
        private final MessageDigest md;

        private static Pool pool = new Pool();

        private Map<String, SoftReference<RolapSchema>> mapUrlToSchema =
            new HashMap<String, SoftReference<RolapSchema>>();


        private Pool() {
            // Initialize the MD5 digester.
            try {
                md = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        static Pool instance() {
            return pool;
        }

        /**
         * Creates an MD5 hash of String.
         *
         * @param value String to create one way hash upon.
         * @return MD5 hash.
         */
        private synchronized String encodeMD5(final String value) {
            md.reset();
            final byte[] bytes = md.digest(value.getBytes());
            return (bytes != null) ? new String(bytes) : null;
        }

        synchronized RolapSchema get(
            final String catalogUrl,
            final String connectionKey,
            final String jdbcUser,
            final String dataSourceStr,
            final Util.PropertyList connectInfo)
        {
            return get(
                catalogUrl,
                connectionKey,
                jdbcUser,
                dataSourceStr,
                null,
                connectInfo);
        }

        synchronized RolapSchema get(
            final String catalogUrl,
            final DataSource dataSource,
            final Util.PropertyList connectInfo)
        {
            return get(
                catalogUrl,
                null,
                null,
                null,
                dataSource,
                connectInfo);
        }

        private RolapSchema get(
            final String catalogUrl,
            final String connectionKey,
            final String jdbcUser,
            final String dataSourceStr,
            final DataSource dataSource,
            final Util.PropertyList connectInfo)
        {
            String key =
                (dataSource == null)
                ? makeKey(catalogUrl, connectionKey, jdbcUser, dataSourceStr)
                : makeKey(catalogUrl, dataSource);

            RolapSchema schema = null;

            String dynProcName = connectInfo.get(
                RolapConnectionProperties.DynamicSchemaProcessor.name());

            String catalogStr = connectInfo.get(
                RolapConnectionProperties.CatalogContent.name());
            if (catalogUrl == null && catalogStr == null) {
                throw MondrianResource.instance()
                    .ConnectStringMandatoryProperties.ex(
                    RolapConnectionProperties.Catalog.name(),
                    RolapConnectionProperties.CatalogContent.name());
            }

            // If CatalogContent is specified in the connect string, ignore
            // everything else. In particular, ignore the dynamic schema
            // processor.
            if (catalogStr != null) {
                dynProcName = null;
                // REVIEW: Are we including enough in the key to make it
                // unique?
                key = catalogStr;
            }

            final boolean useContentChecksum =
                Boolean.parseBoolean(
                    connectInfo.get(
                        RolapConnectionProperties.UseContentChecksum.name()));

            // Use the schema pool unless "UseSchemaPool" is explicitly false.
            final boolean useSchemaPool =
                Boolean.parseBoolean(
                    connectInfo.get(
                        RolapConnectionProperties.UseSchemaPool.name(),
                        "true"));

            // If there is a dynamic processor registered, use it. This
            // implies there is not MD5 based caching, but, as with the previous
            // implementation, if the catalog string is in the connectInfo
            // object as catalog content then it is used.
            if (! Util.isEmpty(dynProcName)) {
                assert catalogStr == null;

                try {
                    @SuppressWarnings("unchecked")
                    final Class<DynamicSchemaProcessor> clazz =
                        (Class<DynamicSchemaProcessor>)
                            Class.forName(dynProcName);
                    final Constructor<DynamicSchemaProcessor> ctor =
                        clazz.getConstructor();
                    final DynamicSchemaProcessor dynProc = ctor.newInstance();
                    catalogStr = dynProc.processSchema(catalogUrl, connectInfo);
                } catch (Exception e) {
                    throw Util.newError(
                        e,
                        "loading DynamicSchemaProcessor " + dynProcName);
                }

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                        "Pool.get: create schema \"" + catalogUrl
                        + "\" using dynamic processor");
                }
            }

            if (!useSchemaPool) {
                schema = new RolapSchema(
                    key,
                    null,
                    catalogUrl,
                    catalogStr,
                    connectInfo,
                    dataSource);

            } else if (useContentChecksum) {
                // Different catalogUrls can actually yield the same
                // catalogStr! So, we use the MD5 as the key as well as
                // the key made above - its has two entries in the
                // mapUrlToSchema Map. We must then also during the
                // remove operation make sure we remove both.

                String md5Bytes = null;
                try {
                    if (catalogStr == null) {
                        // Use VFS to get the content
                        InputStream in = null;
                        try {
                            in = Util.readVirtualFile(catalogUrl);
                            StringBuilder buf = new StringBuilder(1000);
                            int n;
                            while ((n = in.read()) != -1) {
                                buf.append((char) n);
                            }
                            catalogStr = buf.toString();
                        } finally {
                            if (in != null) {
                                in.close();
                            }
                        }
                    }

                    md5Bytes = encodeMD5(catalogStr);
                } catch (Exception ex) {
                    // Note, can not throw an Exception from this method
                    // but just to show that all is not well in Mudville
                    // we print stack trace (for now - better to change
                    // method signature and throw).
                    ex.printStackTrace();
                }

                if (md5Bytes != null) {
                    SoftReference<RolapSchema> ref =
                        mapUrlToSchema.get(md5Bytes);
                    if (ref != null) {
                        schema = ref.get();
                        if (schema == null) {
                            // clear out the reference since schema is null
                            mapUrlToSchema.remove(key);
                            mapUrlToSchema.remove(md5Bytes);
                        }
                    }
                }

                if (schema == null
                    || md5Bytes == null
                    || schema.md5Bytes == null
                    || ! schema.md5Bytes.equals(md5Bytes))
                {
                    schema = new RolapSchema(
                        key,
                        md5Bytes,
                        catalogUrl,
                        catalogStr,
                        connectInfo,
                        dataSource);

                    SoftReference<RolapSchema> ref =
                        new SoftReference<RolapSchema>(schema);
                    if (md5Bytes != null) {
                        mapUrlToSchema.put(md5Bytes, ref);
                    }
                    mapUrlToSchema.put(key, ref);

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                            "Pool.get: create schema \"" + catalogUrl
                            + "\" with MD5");
                    }

                } else if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                        "Pool.get: schema \"" + catalogUrl
                        + "\" exists already with MD5");
                }

            } else {
                SoftReference<RolapSchema> ref = mapUrlToSchema.get(key);
                if (ref != null) {
                    schema = ref.get();
                    if (schema == null) {
                        // clear out the reference since schema is null
                        mapUrlToSchema.remove(key);
                    }
                }

                if (schema == null) {
                    if (catalogStr == null) {
                        schema = new RolapSchema(
                            key,
                            catalogUrl,
                            connectInfo,
                            dataSource);
                    } else {
                        schema = new RolapSchema(
                            key,
                            null,
                            catalogUrl,
                            catalogStr,
                            connectInfo,
                            dataSource);
                    }

                    mapUrlToSchema.put(
                        key,
                        new SoftReference<RolapSchema>(schema));

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                            "Pool.get: create schema \"" + catalogUrl + "\"");
                    }

                } else if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                        "Pool.get: schema \"" + catalogUrl
                        + "\" exists already ");
                }
            }
            return schema;
        }

        synchronized void remove(
            final String catalogUrl,
            final String connectionKey,
            final String jdbcUser,
            final String dataSourceStr)
        {
            final String key = makeKey(
                catalogUrl,
                connectionKey,
                jdbcUser,
                dataSourceStr);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "Pool.remove: schema \"" + catalogUrl
                    + "\" and datasource string \"" + dataSourceStr + "\"");
            }
            remove(key);
        }

        synchronized void remove(
            final String catalogUrl,
            final DataSource dataSource)
        {
            final String key = makeKey(catalogUrl, dataSource);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "Pool.remove: schema \"" + catalogUrl
                    + "\" and datasource object");
            }
            remove(key);
        }

        synchronized void remove(RolapSchema schema) {
            if (schema != null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                        "Pool.remove: schema \"" + schema.name
                        + "\" and datasource object");
                }
                remove(schema.key);
            }
        }

        private void remove(String key) {
            SoftReference<RolapSchema> ref = mapUrlToSchema.get(key);
            if (ref != null) {
                RolapSchema schema = ref.get();
                if (schema != null) {
                    if (schema.md5Bytes != null) {
                        mapUrlToSchema.remove(schema.md5Bytes);
                    }
                    schema.finalCleanUp();
                }
            }
            mapUrlToSchema.remove(key);
        }

        synchronized void clear() {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Pool.clear: clearing all RolapSchemas");
            }

            for (SoftReference<RolapSchema> ref : mapUrlToSchema.values()) {
                if (ref != null) {
                    RolapSchema schema = ref.get();
                    if (schema != null) {
                        schema.finalCleanUp();
                    }
                }
            }
            mapUrlToSchema.clear();
            JdbcSchema.clearAllDBs();
        }

        /**
         * This returns an iterator over a copy of the RolapSchema's container.
         *
         * @return Iterator over RolapSchemas
         */
        synchronized Iterator<RolapSchema> getRolapSchemas() {
            List<RolapSchema> list = new ArrayList<RolapSchema>();
            for (Iterator<SoftReference<RolapSchema>> it =
                mapUrlToSchema.values().iterator(); it.hasNext();)
            {
                SoftReference<RolapSchema> ref = it.next();
                RolapSchema schema = ref.get();
                // Schema is null if already garbage collected
                if (schema != null) {
                    list.add(schema);
                } else {
                    // We will remove the stale reference
                    try {
                        it.remove();
                    } catch (Exception ex) {
                        // Should not happen, so
                        // warn but otherwise ignore
                        LOGGER.warn(ex);
                    }
                }
            }
            return list.iterator();
        }

        synchronized boolean contains(RolapSchema rolapSchema) {
            return mapUrlToSchema.containsKey(rolapSchema.key);
        }


        /**
         * Creates a key with which to identify a schema in the cache.
         */
        private static String makeKey(
            final String catalogUrl,
            final String connectionKey,
            final String jdbcUser,
            final String dataSourceStr)
        {
            final StringBuilder buf = new StringBuilder(100);

            appendIfNotNull(buf, catalogUrl);
            appendIfNotNull(buf, connectionKey);
            appendIfNotNull(buf, jdbcUser);
            appendIfNotNull(buf, dataSourceStr);

            return buf.toString();
        }

        /**
         * Creates a key with which to identify a schema in the cache.
         */
        private static String makeKey(
            final String catalogUrl,
            final DataSource dataSource)
        {
            final StringBuilder buf = new StringBuilder(100);

            appendIfNotNull(buf, catalogUrl);
            buf.append('.');
            buf.append("external#");
            buf.append(System.identityHashCode(dataSource));

            return buf.toString();
        }

        private static void appendIfNotNull(StringBuilder buf, String s) {
            if (s != null) {
                if (buf.length() > 0) {
                    buf.append('.');
                }
                buf.append(s);
            }
        }
    }

    public static Iterator<RolapSchema> getRolapSchemas() {
        return Pool.instance().getRolapSchemas();
    }

    public static boolean cacheContains(RolapSchema rolapSchema) {
        return Pool.instance().contains(rolapSchema);
    }

    public Cube lookupCube(final String cube, final boolean failIfNotFound) {
        RolapCube mdxCube = lookupCube(cube);
        if (mdxCube == null && failIfNotFound) {
            throw MondrianResource.instance().MdxCubeNotFound.ex(cube);
        }
        return mdxCube;
    }

    /**
     * Finds a cube called 'cube' in the current catalog, or return null if no
     * cube exists.
     */
    protected RolapCube lookupCube(final String cubeName) {
        return mapNameToCube.get(Util.normalizeName(cubeName));
    }

    /**
     * Returns an xmlCalculatedMember called 'calcMemberName' in the
     * cube called 'cubeName' or return null if no calculatedMember or
     * xmlCube by those name exists.
     */
    protected MondrianDef.CalculatedMember lookupXmlCalculatedMember(
        final String calcMemberName,
        final String cubeName)
    {
        List<Id.Segment> nameParts = Util.parseIdentifier(calcMemberName);
        for (final MondrianDef.Cube cube : xmlSchema.cubes) {
            if (Util.equalName(cube.name, cubeName)) {
                for (final MondrianDef.CalculatedMember calculatedMember
                        : cube.calculatedMembers)
                {
                    if (Util.equalName(
                            calculatedMember.dimension, nameParts.get(0).name)
                        && Util.equalName(
                            calculatedMember.name,
                            nameParts.get(nameParts.size() - 1).name))
                    {
                        return calculatedMember;
                    }
                }
            }
        }
        return null;
    }

    public List<RolapCube> getCubesWithStar(RolapStar star) {
        List<RolapCube> list = new ArrayList<RolapCube>();
        for (RolapCube cube : mapNameToCube.values()) {
            if (star == cube.getStar()) {
                list.add(cube);
            }
        }
        return list;
    }

    /**
     * Adds a cube to the cube name map.
     * @see #lookupCube(String)
     */
    protected void addCube(final RolapCube cube) {
        mapNameToCube.put(
                Util.normalizeName(cube.getName()),
                cube);
    }

    public boolean removeCube(final String cubeName) {
        final RolapCube cube =
            mapNameToCube.remove(Util.normalizeName(cubeName));
        return cube != null;
    }

    public Cube[] getCubes() {
        Collection<RolapCube> cubes = mapNameToCube.values();
        return cubes.toArray(new RolapCube[cubes.size()]);
    }

    public List<RolapCube> getCubeList() {
        return new ArrayList<RolapCube>(mapNameToCube.values());
    }

    public Hierarchy[] getSharedHierarchies() {
        Collection<RolapHierarchy> hierarchies =
            mapSharedHierarchyNameToHierarchy.values();
        return hierarchies.toArray(new RolapHierarchy[hierarchies.size()]);
    }

    RolapHierarchy getSharedHierarchy(final String name) {
        return mapSharedHierarchyNameToHierarchy.get(name);
    }

    public NamedSet getNamedSet(String name) {
        return mapNameToSet.get(name);
    }

    public Role lookupRole(final String role) {
        return mapNameToRole.get(role);
    }

    public Set<String> roleNames() {
        return mapNameToRole.keySet();
    }

    public FunTable getFunTable() {
        return funTable;
    }

    public Parameter[] getParameters() {
        return parameterList.toArray(
                new Parameter[parameterList.size()]);
    }

    /**
     * Defines a user-defined function in this table.
     *
     * <p>If the function is not valid, throws an error.
     *
     * @param name Name of the function.
     * @param className Name of the class which implements the function.
     *   The class must implement {@link mondrian.spi.UserDefinedFunction}
     *   (otherwise it is a user-error).
     */
    private void defineFunction(
        Map<String, UserDefinedFunction> mapNameToUdf,
        String name,
        String className)
    {
        // Lookup class.
        final Class<UserDefinedFunction> klass;
        try {
            klass = (Class<UserDefinedFunction>) Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw MondrianResource.instance().UdfClassNotFound.ex(
                name,
                className);
        }
        // Instantiate UDF by calling correct constructor.
        final UserDefinedFunction udf = Util.createUdf(klass, name);
        // Validate function.
        validateFunction(udf);
        // Check for duplicate.
        UserDefinedFunction existingUdf = mapNameToUdf.get(name);
        if (existingUdf != null) {
            throw MondrianResource.instance().UdfDuplicateName.ex(name);
        }
        mapNameToUdf.put(name, udf);
    }

    /**
     * Throws an error if a user-defined function does not adhere to the
     * API.
     */
    private void validateFunction(final UserDefinedFunction udf) {
        // Check that the name is not null or empty.
        final String udfName = udf.getName();
        if (udfName == null || udfName.equals("")) {
            throw Util.newInternal(
                "User-defined function defined by class '"
                + udf.getClass() + "' has empty name");
        }
        // It's OK for the description to be null.
        final String description = udf.getDescription();
        Util.discard(description);
        final Type[] parameterTypes = udf.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            Type parameterType = parameterTypes[i];
            if (parameterType == null) {
                throw Util.newInternal(
                    "Invalid user-defined function '"
                    + udfName + "': parameter type #" + i + " is null");
            }
        }
        // It's OK for the reserved words to be null or empty.
        final String[] reservedWords = udf.getReservedWords();
        Util.discard(reservedWords);
        // Test that the function returns a sensible type when given the FORMAL
        // types. It may still fail when we give it the ACTUAL types, but it's
        // impossible to check that now.
        final Type returnType = udf.getReturnType(parameterTypes);
        if (returnType == null) {
            throw Util.newInternal(
                "Invalid user-defined function '"
                + udfName + "': return type is null");
        }
        final Syntax syntax = udf.getSyntax();
        if (syntax == null) {
            throw Util.newInternal(
                "Invalid user-defined function '"
                + udfName + "': syntax is null");
        }
    }

    /**
     * Gets a {@link MemberReader} with which to read a hierarchy. If the
     * hierarchy is shared (<code>sharedName</code> is not null), looks up
     * a reader from a cache, or creates one if necessary.
     *
     * <p>Synchronization: thread safe
     */
    synchronized MemberReader createMemberReader(
        final String sharedName,
        final RolapHierarchy hierarchy,
        final String memberReaderClass)
    {
        MemberReader reader;
        if (sharedName != null) {
            reader = mapSharedHierarchyToReader.get(sharedName);
            if (reader == null) {
                reader = createMemberReader(hierarchy, memberReaderClass);
                // share, for other uses of the same shared hierarchy
                if (false) {
                    mapSharedHierarchyToReader.put(sharedName, reader);
                }
/*
System.out.println("RolapSchema.createMemberReader: "+
"add to sharedHierName->Hier map"+
" sharedName=" + sharedName +
", hierarchy=" + hierarchy.getName() +
", hierarchy.dim=" + hierarchy.getDimension().getName()
);
if (mapSharedHierarchyNameToHierarchy.containsKey(sharedName)) {
System.out.println("RolapSchema.createMemberReader: CONTAINS NAME");
} else {
                mapSharedHierarchyNameToHierarchy.put(sharedName, hierarchy);
}
*/
                if (! mapSharedHierarchyNameToHierarchy.containsKey(
                    sharedName))
                {
                    mapSharedHierarchyNameToHierarchy.put(
                        sharedName, hierarchy);
                }
                //mapSharedHierarchyNameToHierarchy.put(sharedName, hierarchy);
            } else {
//                final RolapHierarchy sharedHierarchy = (RolapHierarchy)
//                        mapSharedHierarchyNameToHierarchy.get(sharedName);
//                final RolapDimension sharedDimension = (RolapDimension)
//                        sharedHierarchy.getDimension();
//                final RolapDimension dimension =
//                    (RolapDimension) hierarchy.getDimension();
//                Util.assertTrue(
//                        dimension.getGlobalOrdinal() ==
//                        sharedDimension.getGlobalOrdinal());
            }
        } else {
            reader = createMemberReader(hierarchy, memberReaderClass);
        }
        return reader;
    }

    /**
     * Creates a {@link MemberReader} with which to Read a hierarchy.
     */
    private MemberReader createMemberReader(
        final RolapHierarchy hierarchy,
        final String memberReaderClass)
    {
        if (memberReaderClass != null) {
            Exception e2;
            try {
                Properties properties = null;
                Class<?> clazz = Class.forName(memberReaderClass);
                Constructor<?> constructor = clazz.getConstructor(
                    RolapHierarchy.class,
                    Properties.class);
                Object o = constructor.newInstance(hierarchy, properties);
                if (o instanceof MemberReader) {
                    return (MemberReader) o;
                } else if (o instanceof MemberSource) {
                    return new CacheMemberReader((MemberSource) o);
                } else {
                    throw Util.newInternal(
                        "member reader class " + clazz
                        + " does not implement " + MemberSource.class);
                }
            } catch (ClassNotFoundException e) {
                e2 = e;
            } catch (NoSuchMethodException e) {
                e2 = e;
            } catch (InstantiationException e) {
                e2 = e;
            } catch (IllegalAccessException e) {
                e2 = e;
            } catch (InvocationTargetException e) {
                e2 = e;
            }
            throw Util.newInternal(
                e2,
                "while instantiating member reader '" + memberReaderClass);
        } else {
            SqlMemberSource source = new SqlMemberSource(hierarchy);
            if (hierarchy.getDimension().isHighCardinality()) {
                LOGGER.debug(
                    "High cardinality for " + hierarchy.getDimension());
                return new NoCacheMemberReader(source);
            } else {
                LOGGER.debug(
                    "Normal cardinality for " + hierarchy.getDimension());
                return new SmartMemberReader(source);
            }
        }
    }

    public SchemaReader getSchemaReader() {
        return new RolapSchemaReader(defaultRole, this);
    }

    /**
     * Creates a {@link DataSourceChangeListener} with which to detect changes to datasources.
     */
    private DataSourceChangeListener createDataSourceChangeListener(
        Util.PropertyList connectInfo)
    {
        DataSourceChangeListener changeListener = null;

        // If CatalogContent is specified in the connect string, ignore
        // everything else. In particular, ignore the dynamic schema
        // processor.
        String dataSourceChangeListenerStr = connectInfo.get(
            RolapConnectionProperties.DataSourceChangeListener.name());

        if (! Util.isEmpty(dataSourceChangeListenerStr)) {
            try {
                Class<?> clazz = Class.forName(dataSourceChangeListenerStr);
                Constructor<?> constructor = clazz.getConstructor();
                changeListener =
                    (DataSourceChangeListener) constructor.newInstance();

/*
                final Class<DataSourceChangeListener> clazz =
                    (Class<DataSourceChangeListener>)
                        Class.forName(dataSourceChangeListenerStr);
                final Constructor<DataSourceChangeListener> ctor =
                    clazz.getConstructor();
                changeListener = ctor.newInstance();
*/
                changeListener =
                    (DataSourceChangeListener) constructor.newInstance();
            } catch (Exception e) {
                throw Util.newError(
                    e,
                    "loading DataSourceChangeListener "
                    + dataSourceChangeListenerStr);
            }

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "RolapSchema.createDataSourceChangeListener: "
                    + "create datasource change listener \""
                    + dataSourceChangeListenerStr);
            }
        }
        return changeListener;
    }


    /**
     * Connection for purposes of parsing and validation. Careful! It won't
     * have the correct locale or access-control profile.
     */
    public RolapConnection getInternalConnection() {
        return internalConnection;
    }

    /**
     * Returns the cached cardinality for the column.
     * The cache is stored in the schema so that queries on different
     * cubes can share them.
     * @return the cardinality map
     */
    Integer getCachedRelationExprCardinality(
        MondrianDef.Relation relation,
        MondrianDef.Expression columnExpr)
    {
        Integer card = null;
        synchronized (relationExprCardinalityMap) {
            Map<MondrianDef.Expression, Integer> exprCardinalityMap =
                relationExprCardinalityMap.get(relation);
            if (exprCardinalityMap != null) {
                card = exprCardinalityMap.get(columnExpr);
            }
        }
        return card;
    }

    /**
     * Sets the cardinality for a given column in cache.
     *
     * @param relation the relation associated with the column expression
     * @param columnExpr the column expression to cache the cardinality for
     * @param cardinality the cardinality for the column expression
     */
    void putCachedRelationExprCardinality(
        MondrianDef.Relation relation,
        MondrianDef.Expression columnExpr,
        Integer cardinality)
    {
        synchronized (relationExprCardinalityMap) {
            Map<MondrianDef.Expression, Integer> exprCardinalityMap =
                relationExprCardinalityMap.get(relation);
            if (exprCardinalityMap == null) {
                exprCardinalityMap =
                    new HashMap<MondrianDef.Expression, Integer>();
                relationExprCardinalityMap.put(relation, exprCardinalityMap);
            }
            exprCardinalityMap.put(columnExpr, cardinality);
        }
    }

    private RoleImpl createDefaultRole() {
        RoleImpl role = new RoleImpl();
        role.grant(this, Access.ALL);
        role.makeImmutable();
        return role;
    }

    private RolapStar makeRolapStar(final MondrianDef.Relation fact) {
        DataSource dataSource = getInternalConnection().getDataSource();
        return new RolapStar(this, dataSource, fact);
    }

    /**
     * <code>RolapStarRegistry</code> is a registry for {@link RolapStar}s.
     */
    class RolapStarRegistry {
        private final Map<String, RolapStar> stars =
            new HashMap<String, RolapStar>();

        RolapStarRegistry() {
        }

        /**
         * Looks up a {@link RolapStar}, creating it if it does not exist.
         *
         * <p> {@link RolapStar.Table#addJoin} works in a similar way.
         */
        synchronized RolapStar getOrCreateStar(
            final MondrianDef.Relation fact)
        {
            String factTableName = fact.toString();
            RolapStar star = stars.get(factTableName);
            if (star == null) {
                star = makeRolapStar(fact);
                stars.put(factTableName, star);
            }
            return star;
        }

        synchronized RolapStar getStar(final String factTableName) {
            return stars.get(factTableName);
        }

        synchronized Collection<RolapStar> getStars() {
            return stars.values();
        }
    }

    private RolapStarRegistry rolapStarRegistry = new RolapStarRegistry();

    public RolapStarRegistry getRolapStarRegistry() {
        return rolapStarRegistry;
    }

    /**
     * Function table which contains all of the user-defined functions in this
     * schema, plus all of the standard functions.
     */
    static class RolapSchemaFunctionTable extends FunTableImpl {
        private final List<UserDefinedFunction> udfList;

        RolapSchemaFunctionTable(Collection<UserDefinedFunction> udfs) {
            udfList = new ArrayList<UserDefinedFunction>(udfs);
        }

        public void defineFunctions(Builder builder) {
            final FunTable globalFunTable = GlobalFunTable.instance();
            for (String reservedWord : globalFunTable.getReservedWords()) {
                builder.defineReserved(reservedWord);
            }
            for (Resolver resolver : globalFunTable.getResolvers()) {
                builder.define(resolver);
            }
            for (UserDefinedFunction udf : udfList) {
                builder.define(new UdfResolver(udf));
            }
        }
    }

    public RolapStar getStar(final String factTableName) {
        return getRolapStarRegistry().getStar(factTableName);
    }

    public Collection<RolapStar> getStars() {
        return getRolapStarRegistry().getStars();
    }

    /**
     * Checks whether there are modifications in the aggregations cache.
     */
    public void checkAggregateModifications() {
        for (RolapStar star : getStars()) {
            star.checkAggregateModifications();
        }
    }

    /**
     * Pushes all modifications of the aggregations to global cache,
     * so other queries can start using the new cache
     */
    public void pushAggregateModificationsToGlobalCache() {
        for (RolapStar star : getStars()) {
            star.pushAggregateModificationsToGlobalCache();
        }
    }

    final RolapNativeRegistry nativeRegistry = new RolapNativeRegistry();

    RolapNativeRegistry getNativeRegistry() {
        return nativeRegistry;
    }

    /**
     * @return Returns the dataSourceChangeListener.
     */
    public DataSourceChangeListener getDataSourceChangeListener() {
        return dataSourceChangeListener;
    }

    /**
     * @param dataSourceChangeListener The dataSourceChangeListener to set.
     */
    public void setDataSourceChangeListener(
        DataSourceChangeListener dataSourceChangeListener)
    {
        this.dataSourceChangeListener = dataSourceChangeListener;
    }

    /**
     * Location of a node in an XML document.
     */
    private interface XmlLocation {
    }
}

// End RolapSchema.java
