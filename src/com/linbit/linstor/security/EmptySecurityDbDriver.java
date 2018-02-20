package com.linbit.linstor.security;

import com.linbit.NoOpObjectDatabaseDriver;
import com.linbit.SingleColumnDatabaseDriver;
import com.linbit.TransactionMgr;
import com.linbit.linstor.annotation.SystemContext;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

@Singleton
public class EmptySecurityDbDriver implements DbAccessor
{
    public static final SingleColumnDatabaseDriver<?, ?> NOOP_COL_DRIVER =
        new NoOpObjectDatabaseDriver<Object, Object>();

    @Inject
    public EmptySecurityDbDriver()
    {
    }

    @Override
    public ResultSet getSignInEntry(Connection dbConn, IdentityName idName) throws SQLException
    {
        return null;
    }

    @Override
    public ResultSet getIdRoleMapEntry(Connection dbConn, IdentityName idName, RoleName rlName) throws SQLException
    {
        return null;
    }

    @Override
    public ResultSet getDefaultRole(Connection dbConn, IdentityName idName) throws SQLException
    {
        return null;
    }

    @Override
    public ResultSet loadIdentities(Connection dbConn) throws SQLException
    {
        return null;
    }

    @Override
    public ResultSet loadSecurityTypes(Connection dbConn) throws SQLException
    {
        return null;
    }

    @Override
    public ResultSet loadRoles(Connection dbConn) throws SQLException
    {
        return null;
    }

    @Override
    public ResultSet loadTeRules(Connection dbConn) throws SQLException
    {
        return null;
    }

    @Override
    public ResultSet loadSecurityLevel(Connection dbConn) throws SQLException
    {
        return null;
    }

    @Override
    public ResultSet loadAuthRequired(Connection dbConn) throws SQLException
    {
        return null;
    }

    @Override
    public void setSecurityLevel(Connection dbConn, SecurityLevel newLevel) throws SQLException
    {
        // no-op
    }

    @Override
    public void setAuthRequired(Connection dbConn, boolean requiredFlag) throws SQLException
    {
        // no-op
    }

    public static class EmptyObjectProtectionDatabaseDriver implements ObjectProtectionDatabaseDriver
    {
        private final ObjectProtection objProt;

        @Inject
        EmptyObjectProtectionDatabaseDriver(@SystemContext AccessContext accCtx)
        {
            objProt = new ObjectProtection(accCtx, null, null);
        }

        @Override
        public void insertOp(ObjectProtection objProtRef, TransactionMgr transMgr) throws SQLException
        {
            // no-op
        }

        @Override
        public void deleteOp(String objPath, TransactionMgr transMgr) throws SQLException
        {
            // no-op
        }

        @Override
        public void insertAcl(
            ObjectProtection objProtRef,
            Role role,
            AccessType grantedAccess,
            TransactionMgr transMgr
        )
            throws SQLException
        {
            // no-op
        }

        @Override
        public void updateAcl(
            ObjectProtection objProtRef,
            Role role,
            AccessType grantedAccess,
            TransactionMgr transMgr
        )
            throws SQLException
        {
            // no-op
        }

        @Override
        public void deleteAcl(ObjectProtection objProtRef, Role role, TransactionMgr transMgr) throws SQLException
        {
            // no-op
        }

        @Override
        public ObjectProtection loadObjectProtection(
            String objPath,
            boolean logWarnIfNotExists,
            TransactionMgr transMgr
        )
            throws SQLException
        {
            return objProt;
        }

        @SuppressWarnings("unchecked")
        @Override
        public SingleColumnDatabaseDriver<ObjectProtection, Identity> getIdentityDatabaseDrier()
        {
            // no-op
            return (SingleColumnDatabaseDriver<ObjectProtection, Identity>) NOOP_COL_DRIVER;
        }

        @SuppressWarnings("unchecked")
        @Override
        public SingleColumnDatabaseDriver<ObjectProtection, Role> getRoleDatabaseDriver()
        {
            // no-op
            return (SingleColumnDatabaseDriver<ObjectProtection, Role>) NOOP_COL_DRIVER;
        }

        @SuppressWarnings("unchecked")
        @Override
        public SingleColumnDatabaseDriver<ObjectProtection, SecurityType> getSecurityTypeDriver()
        {
            // no-op
            return (SingleColumnDatabaseDriver<ObjectProtection, SecurityType>) NOOP_COL_DRIVER;
        }
    }
}
