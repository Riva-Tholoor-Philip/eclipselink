package org.eclipse.persistence.platform.database.oracle.publisher.sqlrefl;

import java.sql.SQLException;
import java.util.Hashtable;
import java.util.Vector;
import org.eclipse.persistence.platform.database.oracle.publisher.PublisherException;
import org.eclipse.persistence.platform.database.oracle.publisher.Util;
import org.eclipse.persistence.platform.database.oracle.publisher.viewcache.ViewCache;
import static org.eclipse.persistence.platform.database.oracle.publisher.Util.SCHEMA_IF_NEEDED;
import static org.eclipse.persistence.platform.database.oracle.publisher.Util.printTypeWithLength;

@SuppressWarnings("unchecked")
public class SqlType extends Type {
    // The following type constants are used by the database
    // to distinguish the various Opaque and SQLJ Object type
    // flavours

    public static final int CODE_OPAQUE = 58;
    public static final int CODE_SQLJTYPE = 108;

    public static final int SQLJTYPE_SQLDATA = 1;
    public static final int SQLJTYPE_CUSTOMDATUM = 2;
    public static final int SQLJTYPE_SERIALIZABLE = 3;
    public static final int SQLJTYPE_ORADATA = 5;
    public static final int SQLJTYPE_BOTH = 6;
    public static final int SQLJTYPE_BOTH8I = 7;

    // The following type constants are internal to JDBC
    public static final int ORACLE_TYPES_NCHAR = -72054;
    public static final int ORACLE_TYPES_NCLOB = -72055;
    public static final int ORACLE_TYPES_BOOLEAN = -72056;
    public static final int ORACLE_TYPES_TBD = -72057;

    public SqlName getSqlName() {
        return (SqlName)m_name;
    }

    public boolean isRef() {
        return getTypecode() == OracleTypes.REF;
    }

    public boolean isCollection() {
        return (getTypecode() == OracleTypes.ARRAY || getTypecode() == OracleTypes.TABLE || isPlsqlTable());
    }

    public boolean isPlsqlTable() {
        return (getTypecode() == OracleTypes.PLSQL_INDEX_TABLE
            || getTypecode() == OracleTypes.PLSQL_NESTED_TABLE || getTypecode() == OracleTypes.PLSQL_VARRAY_TABLE);
    }

    public boolean isPlsqlRecord() {
        return (getTypecode() == OracleTypes.PLSQL_RECORD);
    }

    public boolean isOpaque() {
        return getTypecode() == OracleTypes.OPAQUE;
    }

    public boolean isJavaStruct() {
        return getTypecode() == OracleTypes.JAVA_STRUCT;
    }

    public boolean isSqlStatement() {
        return false;
    }

    public int getSqljKind() {
        return 0;
    }

    public boolean isStruct() {
        return getTypecode() == OracleTypes.STRUCT;
    }

    /**
     * Returns the version string of a SqlType.
     * 
     * * @return the version string of the type.
     */
    public String getVersion() {
        return m_version;
    }

    /**
     * Set the version string of a declared type.
     * 
     * * @param version the version string of the type
     */
    public void setVersion(String version) {
        m_version = version;
    }

    /**
     * Get the attribute hashtable associated with a SqlType. The attributes in the hashtable were
     * registered via addAttribute().
     */
    public Hashtable getAttributes() {
        return (Hashtable)getAnnotation();
    }

    /**
     * Add an attribute to the collection of attributes of a SqlType. JPub only adds attributes
     * mentioned in the input file.
     */
    public void addAttribute(String sqlField, String javaField) {
        // TODO: check for duplicate Java field names!
        String dbField = m_viewCache.dbifyName(sqlField);
        if (javaField == null) {
            javaField = sqlField;
        }

        // System.out.println("[SqlType] Add "+ this +" field: "+ dbField + " -> " + javaField);
        // //D+

        Hashtable h = (Hashtable)getAnnotation();
        Vector v = getNamedTranslations();

        if (h == null) {
            h = new Hashtable();
            setAnnotation(h);
        }

        if (v == null) {
            v = new Vector();
            setNamedTranslations(v);
        }

        Object old = h.put(dbField, javaField);
        v.addElement(sqlField);
        if (old != null) {
            throw new IllegalArgumentException("Redeclaration of field " + dbField + " in " + this
                + "!");
        }
    }

    /**
     * SqlType CONSTRUCTORS
     */
    /**
     * This constructor is used for user-defined and REF types. It may not be called more than once
     * for the same non-null sqlName.
     */
    protected SqlType(SqlName sqlName, int typecode, boolean generateMe, SqlType parentType,
        SqlReflectorImpl reflector) {
        this(sqlName, typecode, generateMe, false, parentType, reflector);
        m_isReused = false;
    }

    SqlType(SqlName sqlName, int typecode, boolean generateMe, boolean isPrimitive,
        SqlType parentType, SqlReflectorImpl reflector) {
        super(sqlName, typecode, isPrimitive);
        m_isReused = false;
        m_reflector = reflector;
        if (m_reflector != null) {
            m_viewCache = m_reflector.getViewCache();
        }
        if (sqlName != null) { // For SqlRefType, sqlName==null
            if (m_reflector != null) {
                m_reflector.addType(sqlName, this, generateMe);
            }
        }
        m_parentType = parentType;
    }

    /**
     * This constructor is used for predefined SQL types. It may not be called more than once for
     * the same name.
     */
    public SqlType(String name, int typecode) {
        super(new SqlName(null, name, true, false, true, null, null, null, null/* reflector */),
            typecode, true);
    }

    /**
     * This constructor is used for predefined PL/SQL to SQL type mapping.
     */
    SqlType(SqlName name, int typecode) {
        super(name, typecode, true);
    }

    // Determine whether "this" type depends on "t"
    // For instance, if "t" is a field of "this",
    // or the element type of "this"
    private Vector m_dependTypes = null;

    boolean dependsOn(SqlType t) {
        if (m_dependTypes == null) {
            m_dependTypes = new Vector();
            if (isPlsqlRecord() || this instanceof DefaultArgsHolderType) {
                try {
                    Field[] fields = getDeclaredFields(true);
                    for (int i = 0; i < fields.length; i++) {
                        SqlType st = (SqlType)fields[i].getType();
                        if (st.isPlsqlRecord() || st.isPlsqlTable()) {
                            m_dependTypes.addElement(st);
                        }
                    }
                }
                catch (Exception e) {
                    System.err.println(e.getMessage());
                }
            }
            else if (this instanceof PlsqlTableType) { // to fend off PlsqlIndexTableType
                try {
                    SqlType st = (SqlType)((PlsqlTableType)this).getComponentType();
                    if (st.isPlsqlRecord() || st.isPlsqlTable()) {
                        m_dependTypes.addElement(st);
                    }
                }
                catch (Exception e) {
                    System.err.println(e.getMessage());
                }
            }
        }
        if (m_dependTypes.contains(t)) {
            return true;
        }
        for (int i = 0; i < m_dependTypes.size(); i++) {
            if (((SqlType)m_dependTypes.elementAt(i)).dependsOn(t)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasConversion() {
        // For SqlRefType, getSqlName()==null
        if (getSqlName() == null) {
            return false;
        }
        return getSqlName().hasConversion();
    }

    /**
     * Reports the SQL type into which the PL/SQL can be converted. Returns null if not a PL/SQL
     * type or if it doed not have a user-defined conversion.
     */
    public String getTargetTypeName() {
        return getSqlName().getTargetTypeName();
    }

    public String getTargetTypeName(int schemaNames) {
        return getSqlName().getFullTargetTypeName(schemaNames);
    }

    public String getTypeName() {
        return getSqlName().getTypeName();
    }

    /**
     * Returns the PL/SQL function to be used for converting this PL/SQL into a SQL type.
     * <p/>
     * Returns null if this is not a PL/SQL type or if it does not have user-defined conversions.
     */
    static Hashtable m_convFuns = new Hashtable();

    public String getOutOfConversion() {
        // For SqlRefType, getSqlName()==null
        if (getSqlName() == null) {
            return null;
        }
        return getSqlName().getOutOfConversion();
    }

    /**
     * Returns the PL/SQL function to be used for converting a SQL type into this PL/SQL type.
     * <p/>
     * Returns null if this is not a PL/SQL type or if it does not have user-defined conversions.
     */
    public String getIntoConversion() {
        // For SqlRefType, getSqlName()==null
        if (getSqlName() == null) {
            return null;
        }
        return getSqlName().getIntoConversion();
    }

    // Return the conversion function name
    // prefixed with package name,
    // if the conversion function is a generated one
    public String getOutOfConversionQualified() {
        // For SqlRefType, getSqlName()==null
        if (getSqlName() == null) {
            return null;
        }
        return getSqlName().getOutOfConversionQualified();
    }

    // Return the conversion function name
    // prefixed with package name,
    // if the conversion function is a generated one
    public String getIntoConversionQualified() {
        // For SqlRefType, getSqlName()==null
        if (getSqlName() == null) {
            return null;
        }
        return getSqlName().getIntoConversionQualified();
    }

    // SQL declaration of the generated SQL types
    public String getSqlTypeDecl() throws SQLException, PublisherException {
        String sqlTypeDecl = "";
        if (isPlsqlRecord() && !getSqlName().isReused()) {
            sqlTypeDecl += "CREATE OR REPLACE TYPE " + getTargetTypeName() + " AS OBJECT (\n";
            Field[] fields = ((PlsqlRecordType)this).getFields(true);
            for (int i = 0; i < fields.length; i++) {
                if (i != 0) {
                    sqlTypeDecl += ",\n";
                }
                sqlTypeDecl += "      " + Util.unreserveSql(fields[i].getName()) + " ";
                sqlTypeDecl += fields[i].printTypeWithLength(SCHEMA_IF_NEEDED);
            }
            sqlTypeDecl += "\n);";
        }
        else if (isPlsqlTable() && !getSqlName().isReused()) {
            PlsqlTableType plType = (PlsqlTableType)this;
            SqlType eleType = (SqlType)plType.getComponentType();
            sqlTypeDecl += "CREATE OR REPLACE TYPE " + getTargetTypeName();
            sqlTypeDecl += " AS TABLE OF ";
            sqlTypeDecl += printTypeWithLength(eleType.getTargetTypeName(SCHEMA_IF_NEEDED), plType
                .getElemTypeLength(), plType.getElemTypePrecision(), plType.getElemTypeScale());
            sqlTypeDecl += ";";
        }
        else if ((this instanceof DefaultArgsHolderType) && !getSqlName().isReused()) {
            DefaultArgsHolderType holder = (DefaultArgsHolderType)this;
            sqlTypeDecl += "CREATE OR REPLACE TYPE " + getTypeName() + " AS OBJECT (\n";
            Field vfield = holder.getFields(false)[0];
            sqlTypeDecl += "      " + vfield.getName() + " ";
            sqlTypeDecl += vfield.printTypeWithLength();
            sqlTypeDecl += "\n);";
        }
        return sqlTypeDecl;
    }

    // Drop the generated SQL type
    public String getSqlTypeDrop() throws SQLException, PublisherException {
        return getSqlName().isReused() ? "" : "DROP TYPE " + getTargetTypeName() + " FORCE; \n"
            + "show errors\n";
    }

    // PL/SQL package declaration for conversion functions
    // between SQL and PL/SQL
    public String getConversionFunDecl() throws PublisherException, SQLException {
        if (!hasConversion()) {
            return "";
        }
        String sqlConvPkgDecl = "";

        // If this type is a ROWTYPE, then redefine it as a normal PL/SQL record
        if (getSqlName().isRowType()) {
            sqlConvPkgDecl += "\t-- Redefine a PL/SQL RECORD type originally defined via CURSOR%ROWTYPE"
                + "\n";
            sqlConvPkgDecl += "\tTYPE " + getTypeName() + " IS RECORD (\n";
            Field[] fields = ((PlsqlRecordType)this).getFields(true);
            for (int i = 0; i < fields.length; i++) {
                if (i != 0) {
                    sqlConvPkgDecl += ",\n";
                }
                sqlConvPkgDecl += "\t\t" + fields[i].getName() + " "
                    + fields[i].printTypeWithLength();
            }
            sqlConvPkgDecl += ");";
        }

        // PL/SQL to SQL
        sqlConvPkgDecl += "\t-- Declare the conversion functions the PL/SQL type " + getTypeName()
            + "\n" + "\tFUNCTION " + getOutOfConversion() + "(aPlsqlItem " + getTypeName() + ")\n"
            + " \tRETURN " + getTargetTypeName() + ";\n";

        // SQL to PL/SQL
        sqlConvPkgDecl += "\tFUNCTION " + getIntoConversion() + "(aSqlItem " + getTargetTypeName()
            + ")\n" + "\tRETURN " + getTypeName() + ";";
        return sqlConvPkgDecl;
    }

    // PL/SQL package body for conversion functions between SQL and PL/SQL
    public String getConversionPL2SQLFunBody() throws SQLException, PublisherException {
        if (!hasConversion()) {
            return "";
        }
        // PL/SQL to SQL
        String sqlConvPkgBody = "\tFUNCTION " + getOutOfConversion() + "(aPlsqlItem "
            + getTypeName() + ")\n " + "\tRETURN " + getTargetTypeName() + " IS \n" + "\taSqlItem "
            + getTargetTypeName() + "; \n" + "\tBEGIN \n"
            + getOutOfConvStmts("\t\t", "aPlsqlItem", "aSqlItem") + "\t\tRETURN aSqlItem;\n"
            + "\tEND " + getOutOfConversion() + ";\n";
        return sqlConvPkgBody;
    }

    // PL/SQL package body for conversion functions between SQL and PL/SQL
    public String getConversionSQL2PLFunBody() throws SQLException, PublisherException {
        if (!hasConversion()) {
            return "";
        }
        // SQL to PL/SQL
        String sqlConvPkgBody = "\tFUNCTION " + getIntoConversion() + "(aSqlItem "
            + getTargetTypeName() + ") \n" + "\tRETURN " + getTypeName() + " IS \n"
            + "\taPlsqlItem " + getTypeName() + "; \n" + "\tBEGIN \n"
            + getIntoConvStmts("\t\t", "aSqlItem", "aPlsqlItem") + "\t\tRETURN aPlsqlItem;\n"
            + "\tEND " + getIntoConversion() + ";\n";
        return sqlConvPkgBody;
    }

    // both conversion functions
    public String getBothConversions() throws SQLException, PublisherException {
        return getConversionSQL2PLFunBody() + getConversionPL2SQLFunBody();
    }

    // Return assignment statements from SQL objects to PL/SQL records
    public String getIntoConvStmts(String formatPrefix, String maybeSql, String maybePlsql)
        throws SQLException, PublisherException {
        if (!isPlsqlRecord() && !isPlsqlTable()) {
            return formatPrefix + maybePlsql + "." + getName() + " := " + maybeSql + getName()
                + ";\n";
        }
        String stmts = "";
        if (isPlsqlRecord()) {
            Field[] fields = getFields(true);
            for (int i = 0; i < java.lang.reflect.Array.getLength(fields); i++) {
                stmts += formatPrefix
                    + maybePlsql
                    + "."
                    + fields[i].getName()
                    + " := "
                    + ((fields[i].getType().hasConversion() && fields[i].getType()
                        .getIntoConversion() != null) ? fields[i].getType().getIntoConversion()
                        + "(" + maybeSql + "." + Util.unreserveSql(fields[i].getName()) + ");\n"
                        : maybeSql + "." + Util.unreserveSql(fields[i].getName()) + ";\n");
            }
        }
        else { // if (isPlsqlTable())
            Type compType = ((PlsqlTableType)this).getComponentType();
            if (getTypecode() == OracleTypes.PLSQL_NESTED_TABLE
                || getTypecode() == OracleTypes.PLSQL_VARRAY_TABLE) {
                stmts += formatPrefix + maybePlsql + " := " + getTypeName() + "();\n";
                stmts += formatPrefix + maybePlsql + ".EXTEND" + "(" + maybeSql + ".COUNT);\n";
            }
            stmts += formatPrefix
                + "IF "
                + maybeSql
                + ".COUNT>0 THEN\n"
                + formatPrefix
                + "FOR I IN 1.."
                + maybeSql
                + ".COUNT LOOP\n"
                + formatPrefix
                + "\t"
                + maybePlsql
                + "(I)"
                + " := "
                + ((compType.hasConversion() && compType.getIntoConversion() != null) ? compType
                    .getIntoConversion()
                    + "(" + maybeSql + "(I)" + ");\n" : maybeSql + "(I);\n") + formatPrefix
                + "END LOOP; \n" + formatPrefix + "END IF;\n";
        }
        return stmts;
    }

    // Return assignment statements from PL/SQL records to SQL objects
    public String getOutOfConvStmts(String formatPrefix, String maybePlsql, String maybeSql)
        throws SQLException, PublisherException {
        if (!isPlsqlRecord() && !isPlsqlTable()) {
            return formatPrefix + maybeSql + " := " + maybePlsql + ";\n";
        }
        String stmts = "";
        if (isPlsqlRecord()) {
            Field[] fields = getFields(true);
            stmts += formatPrefix + maybeSql + " := " + getTargetTypeName() + "(NULL";
            for (int i = 1; i < fields.length; i++) {
                stmts += ", NULL";
            }
            stmts += ");\n";

            for (int i = 0; i < fields.length; i++) {
                stmts += formatPrefix
                    + maybeSql
                    + "."
                    + Util.unreserveSql(fields[i].getName())
                    + " := "
                    + ((fields[i].getType().hasConversion() && fields[i].getType()
                        .getOutOfConversion() != null) ? fields[i].getType().getOutOfConversion()
                        + "(" + maybePlsql + "." + fields[i].getName() + ");\n" : maybePlsql + "."
                        + fields[i].getName() + ";\n");
            }
        }
        else { // if (isPlsqlTable())
            Type compType = ((PlsqlTableType)this).getComponentType();
            stmts += formatPrefix
                + maybeSql
                + " := "
                + getTargetTypeName()
                + "();\n"
                + formatPrefix
                + maybeSql
                + ".EXTEND("
                + maybePlsql
                + ".COUNT);\n"
                + formatPrefix
                + "IF "
                + maybePlsql
                + ".COUNT>0 THEN\n"
                + formatPrefix
                + "FOR I IN "
                + maybePlsql
                + ".FIRST.."
                + maybePlsql
                + ".LAST LOOP\n"
                + formatPrefix
                + "\t"
                + maybeSql
                + "(I + 1 - "
                + maybePlsql
                + ".FIRST)"
                + " := "
                + ((compType.hasConversion() || compType.getOutOfConversion() != null) ? compType
                    .getOutOfConversion()
                    + "(" + maybePlsql + "(I)" + ");\n" : maybePlsql + "(I);\n") + formatPrefix
                + "END LOOP; \n" + formatPrefix + "END IF; \n";
        }
        return stmts;
    }

    private String m_version;
    protected SqlReflectorImpl m_reflector; // null for predefined types
    protected ViewCache m_viewCache;
    protected SqlType m_parentType;
    protected boolean m_isReused;
}
