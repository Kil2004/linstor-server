package com.linbit.linstor.core;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.name.Names;

import com.linbit.linstor.core.identifier.FreeSpaceMgrName;
import com.linbit.linstor.core.objects.FreeSpaceMgr;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.propscon.PropsContainerFactory;
import com.linbit.linstor.transaction.TransactionMap;
import com.linbit.linstor.transaction.manager.TransactionMgr;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.util.Map;
import java.util.TreeMap;

public class ControllerCoreModule extends AbstractModule
{
    private static final String DB_CONTROLLER_PROPSCON_INSTANCE_NAME = "/CTRLCFG";

    @Override
    protected void configure()
    {
        bind(String.class).annotatedWith(Names.named(CoreModule.MODULE_NAME))
            .toInstance(LinStor.CONTROLLER_MODULE);

        bind(FreeSpaceMgrMap.class).to(FreeSpaceMgrMapImpl.class);
    }

    @Provides
    @Singleton
    @Named(LinStor.CONTROLLER_PROPS)
    public Props createControllerPropsContainer(PropsContainerFactory propsContainerFactory)
    {
        return propsContainerFactory.create(DB_CONTROLLER_PROPSCON_INSTANCE_NAME);
    }

    public interface FreeSpaceMgrMap extends Map<FreeSpaceMgrName, FreeSpaceMgr>
    {
    }

    @Singleton
    public static class FreeSpaceMgrMapImpl
        extends TransactionMap<FreeSpaceMgrName, FreeSpaceMgr> implements FreeSpaceMgrMap
    {
        @Inject
        public FreeSpaceMgrMapImpl(Provider<TransactionMgr> transMgrProvider)
        {
            super(new TreeMap<>(), null, transMgrProvider);
        }
    }
}
