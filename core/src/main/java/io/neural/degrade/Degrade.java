package io.neural.degrade;

import io.neural.NURL;
import io.neural.common.Constants;
import io.neural.common.Identity;
import io.neural.common.Identity.Switch;
import io.neural.common.OriginalCall;
import io.neural.common.config.StoreConfig;
import io.neural.common.config.IStore;
import io.neural.degrade.DegradeConfig.Config;
import io.neural.degrade.DegradeConfig.GlobalConfig;
import io.neural.degrade.DegradeConfig.Strategy;
import io.neural.extension.Extension;
import io.neural.extension.ExtensionLoader;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The Degrade Center.
 *
 * @author lry
 */
@Slf4j
@Getter
public enum Degrade {

    DEGRADE;

    public static final String MODULE = DegradeConfigCenter.class.getAnnotation(Extension.class).value();

    private NURL nurl = null;
    private volatile boolean isStart = false;

    private IStore store;
    private StoreConfig<Config, GlobalConfig> storeConfig = null;

    private volatile Map<Identity, Object> mockDataMap = new HashMap<>();
    private final ConcurrentMap<Identity, Config> configs = new ConcurrentHashMap<>();
    private final ConcurrentMap<Identity, DegradeStatistics> degradeStatistics = new ConcurrentHashMap<>();


    /**
     * The add degrade
     *
     * @param degradeConfig
     * @throws Exception
     */
    public void addDegrade(DegradeConfig degradeConfig) throws Exception {
        Identity identity = degradeConfig.getIdentity();
        identity.setApplication(System.getProperty(Constants.APP_NAME_KEY, identity.getApplication()));

        Config config = degradeConfig.getConfig();
        configs.put(identity, config);
        degradeStatistics.put(identity, new DegradeStatistics());
        mockDataMap.put(identity, DegradeConfig.mockData(config.getMock(), config.getClazz(), config.getData()));
    }

    /**
     * The start of degrade
     *
     * @param nurl
     */
    public void start(NURL nurl) {
        if (isStart) {
            log.warn("The degrade already is started");
            return;
        }

        this.nurl = nurl;

        // starting store
        this.store = ExtensionLoader.getLoader(IStore.class).getExtension(nurl.getProtocol());
        this.store.start(nurl);


        // initialize config center and initialize config center store
        this.storeConfig = ExtensionLoader.getLoader(StoreConfig.class).getExtension(nurl.getPath());

        // add degrade global config data to remote
        GlobalConfig globalConfig = storeConfig.queryGlobalConfig();
        if (null == globalConfig) {
            storeConfig.addGlobalConfig(new GlobalConfig());
        }
        // add degrade config data to remote
        configs.forEach(((identity, config) -> {
            storeConfig.putConfig(identity, config);
            if (null == storeConfig.queryConfig(identity)) {
                storeConfig.addConfig(identity, config);
            }
        }));
        storeConfig.start();

        // add shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::destroy));
        this.isStart = true;
    }

    /**
     * The process of degrade
     *
     * @param identity
     * @param originalCall
     * @return
     * @throws Throwable
     */
    public Object doDegrade(Identity identity, OriginalCall originalCall) throws Throwable {
        // the no started
        if (!isStart) {
            return originalCall.call();
        }

        GlobalConfig globalConfig = storeConfig.getGlobalConfig();
        // the check global config of degrade
        if (null == globalConfig || null == globalConfig.getEnable()) {
            return originalCall.call();
        }

        // the check degrade object
        if (null == identity || !configs.containsKey(identity)) {
            return originalCall.call();
        }

        Config config = configs.get(identity);
        // the check config and degrade enable is null
        if (null == config || null == config.getEnable()) {
            return originalCall.call();
        }

        // the check degrade level
        if (null == config.getLevel() || null == globalConfig.getLevel()) {
            return originalCall.call();
        }


        // total request traffic
        degradeStatistics.get(identity).totalRequestTraffic();

        // the check degrade enable, Switch.OFF is opened if degrade
        if (Switch.OFF == config.getEnable()) {
            return this.doDegradeCallWrapper(identity, config.getStrategy(), originalCall);
        }

        // the check Config.Level.order <= GlobalConfig.Level.order<=
        if (config.getLevel().getOrder() <= globalConfig.getLevel().getOrder()) {
            return this.doDegradeCallWrapper(identity, config.getStrategy(), originalCall);
        }

        return this.doOriginalCallWrapper(identity, originalCall);
    }

    /**
     * The do original call wrapper
     *
     * @param identity
     * @param originalCall
     * @return
     * @throws Throwable
     */
    private Object doOriginalCallWrapper(Identity identity, OriginalCall originalCall) throws Throwable {
        DegradeStatistics statistics = degradeStatistics.get(identity);

        // increment traffic
        statistics.incrementTraffic();
        long startTime = System.currentTimeMillis();

        try {
            return originalCall.call();
        } catch (Throwable t) {
            // total exception traffic
            statistics.exceptionTraffic(t);
            throw t;
        } finally {
            // decrement traffic
            statistics.decrementTraffic(startTime);
        }
    }

    /**
     * The do degrade call wrapper
     *
     * @param identity
     * @param strategy
     * @param degradeCall
     * @return
     * @throws Throwable
     */
    private Object doDegradeCallWrapper(
            Identity identity, Strategy strategy, OriginalCall degradeCall) throws Throwable {
        // degrade traffic
        degradeStatistics.get(identity).degradeTraffic();
        // degrade strategy
        switch (strategy) {
            case FALLBACK:
                return degradeCall.fallback();
            case MOCK:
                return mockDataMap.get(identity);
            case NON:
            default:
        }

        // no degrade traffic
        return degradeCall.call();
    }

    /**
     * The destroy of degrade
     */
    public void destroy() {
        this.isStart = false;
        if (null != storeConfig) {
            storeConfig.destroy();
        }
        if (null != store) {
            store.destroy();
        }
    }

}
