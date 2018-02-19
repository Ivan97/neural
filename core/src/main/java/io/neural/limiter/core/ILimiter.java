package io.neural.limiter.core;

import io.neural.common.OriginalCall;
import io.neural.extension.NPI;
import io.neural.limiter.LimiterConfig;
import io.neural.limiter.LimiterStatistics;

/**
 * The Limiter Interface.
 *
 * @author lry
 * @apiNote The default implement is local
 */
@NPI("local")
public interface ILimiter {

    /**
     * The get config of limiter.
     *
     * @return The LimiterConfig
     */
    LimiterConfig getLimiterConfig();

    /**
     * The refresh in-memory data.
     *
     * @param limiterConfig The LimiterConfig
     * @return true is success
     * @throws Exception The Exception is execute refresh LimiterConfig
     */
    boolean refresh(LimiterConfig limiterConfig) throws Exception;

    /**
     * The process original call.
     *
     * @param originalCall The Limiter Config
     * @return The object of OriginalCall
     * @throws Throwable The Exception is execute doOriginalCall
     */
    Object doOriginalCall(OriginalCall originalCall) throws Throwable;

    /**
     * The get statistics of limiter.
     *
     * @return The Limiter Statistics
     */
    LimiterStatistics getStatistics();

    /**
     * The destroy of limiter.
     */
    void destroy();

}
